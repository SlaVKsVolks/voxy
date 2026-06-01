package me.cortex.voxy.client.sodium.provider;

public record VoxyTerrainProviderDiagnostics(
        VoxyFarTerrainProviderSnapshot policy,
        long exactOwnedCells,
        long lodOwnedCells,
        long parentFallbackCells,
        long invalidRejectedCells,
        long parentChildConflictCells,
        long boundaryExactSections,
        long boundaryParentFallbackSections,
        long boundaryRejectedSections,
        long boundaryMissingSections,
        long invalidRenderedSections,
        long boundarySourceRealChunkSections,
        long boundarySourceSurfacePreviewSections,
        long boundarySourceSyntheticPreviewSections,
        long boundarySourceUnknownSections,
        long boundarySourceUntrustedRealSections,
        long boundaryDegradedPreviewSections,
        long unsupportedPassSkips,
        long irisFailClosedSkips,
        long solidPassMeshes,
        long cutoutPassMeshes,
        long translucentPassMeshes,
        long fluidPassMeshes,
        long boundaryQueueDepth,
        long farQueueDepth,
        String terrainCoverageVerdict,
        String visualSourceVerdict,
        String providerRenderAuthorityVerdict
) {
    public long boundaryMissingRequiredCells() {
        return this.boundaryMissingSections;
    }

    public long parentChildVisualConflicts() {
        return this.parentChildConflictCells;
    }

    public long unsupportedPassRenderedSections() {
        return this.cutoutPassMeshes + this.fluidPassMeshes + this.translucentPassMeshes;
    }

    public String sodiumMaterialParityVerdict() {
        if (this.invalidRenderedSections > 0 || this.unsupportedPassRenderedSections() > 0) {
            return "FAIL";
        }
        return "PASS";
    }

    public static VoxyTerrainProviderDiagnostics empty(VoxyFarTerrainProviderSnapshot policy) {
        return new VoxyTerrainProviderDiagnostics(
                policy,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                VoxyFarTerrainProvider.UNKNOWN_NO_BOUNDARY_REQUESTS,
                VoxyFarTerrainProvider.UNKNOWN_NO_BOUNDARY_REQUESTS,
                VoxyFarTerrainProvider.UNKNOWN_NO_BOUNDARY_REQUESTS
        );
    }

    public VoxyTerrainProviderDiagnostics withPolicy(VoxyFarTerrainProviderSnapshot policy) {
        return new VoxyTerrainProviderDiagnostics(
                policy,
                this.exactOwnedCells,
                this.lodOwnedCells,
                this.parentFallbackCells,
                this.invalidRejectedCells,
                this.parentChildConflictCells,
                this.boundaryExactSections,
                this.boundaryParentFallbackSections,
                this.boundaryRejectedSections,
                this.boundaryMissingSections,
                this.invalidRenderedSections,
                this.boundarySourceRealChunkSections,
                this.boundarySourceSurfacePreviewSections,
                this.boundarySourceSyntheticPreviewSections,
                this.boundarySourceUnknownSections,
                this.boundarySourceUntrustedRealSections,
                this.boundaryDegradedPreviewSections,
                this.unsupportedPassSkips,
                this.irisFailClosedSkips,
                this.solidPassMeshes,
                this.cutoutPassMeshes,
                this.translucentPassMeshes,
                this.fluidPassMeshes,
                this.boundaryQueueDepth,
                this.farQueueDepth,
                this.terrainCoverageVerdict,
                this.visualSourceVerdict,
                this.providerRenderAuthorityVerdict
        );
    }

    public VoxyTerrainProviderDiagnostics withQueues(long boundaryQueueDepth, long farQueueDepth) {
        return new VoxyTerrainProviderDiagnostics(
                this.policy,
                this.exactOwnedCells,
                this.lodOwnedCells,
                this.parentFallbackCells,
                this.invalidRejectedCells,
                this.parentChildConflictCells,
                this.boundaryExactSections,
                this.boundaryParentFallbackSections,
                this.boundaryRejectedSections,
                this.boundaryMissingSections,
                this.invalidRenderedSections,
                this.boundarySourceRealChunkSections,
                this.boundarySourceSurfacePreviewSections,
                this.boundarySourceSyntheticPreviewSections,
                this.boundarySourceUnknownSections,
                this.boundarySourceUntrustedRealSections,
                this.boundaryDegradedPreviewSections,
                this.unsupportedPassSkips,
                this.irisFailClosedSkips,
                this.solidPassMeshes,
                this.cutoutPassMeshes,
                this.translucentPassMeshes,
                this.fluidPassMeshes,
                boundaryQueueDepth,
                farQueueDepth,
                this.terrainCoverageVerdict,
                this.visualSourceVerdict,
                this.providerRenderAuthorityVerdict
        );
    }

    public VoxyTerrainProviderDiagnostics withPassMesh(VoxyTerrainPass pass) {
        return new VoxyTerrainProviderDiagnostics(
                this.policy,
                this.exactOwnedCells,
                this.lodOwnedCells,
                this.parentFallbackCells,
                this.invalidRejectedCells,
                this.parentChildConflictCells,
                this.boundaryExactSections,
                this.boundaryParentFallbackSections,
                this.boundaryRejectedSections,
                this.boundaryMissingSections,
                this.invalidRenderedSections,
                this.boundarySourceRealChunkSections,
                this.boundarySourceSurfacePreviewSections,
                this.boundarySourceSyntheticPreviewSections,
                this.boundarySourceUnknownSections,
                this.boundarySourceUntrustedRealSections,
                this.boundaryDegradedPreviewSections,
                this.unsupportedPassSkips,
                this.irisFailClosedSkips,
                this.solidPassMeshes + (pass == VoxyTerrainPass.SOLID ? 1 : 0),
                this.cutoutPassMeshes + (pass == VoxyTerrainPass.CUTOUT ? 1 : 0),
                this.translucentPassMeshes + (pass == VoxyTerrainPass.TRANSLUCENT ? 1 : 0),
                this.fluidPassMeshes + (pass == VoxyTerrainPass.FLUID ? 1 : 0),
                this.boundaryQueueDepth,
                this.farQueueDepth,
                this.terrainCoverageVerdict,
                this.visualSourceVerdict,
                this.providerRenderAuthorityVerdict
        );
    }

    public VoxyTerrainProviderDiagnostics withBoundarySource(
            long boundarySourceRealChunkSections,
            long boundarySourceSurfacePreviewSections,
            long boundarySourceSyntheticPreviewSections,
            long boundarySourceUnknownSections,
            long boundarySourceUntrustedRealSections,
            long boundaryDegradedPreviewSections,
            String visualSourceVerdict
        ) {
        return new VoxyTerrainProviderDiagnostics(
                this.policy,
                this.exactOwnedCells,
                this.lodOwnedCells,
                this.parentFallbackCells,
                this.invalidRejectedCells,
                this.parentChildConflictCells,
                this.boundaryExactSections,
                this.boundaryParentFallbackSections,
                this.boundaryRejectedSections,
                this.boundaryMissingSections,
                this.invalidRenderedSections,
                boundarySourceRealChunkSections,
                boundarySourceSurfacePreviewSections,
                boundarySourceSyntheticPreviewSections,
                boundarySourceUnknownSections,
                boundarySourceUntrustedRealSections,
                boundaryDegradedPreviewSections,
                this.unsupportedPassSkips,
                this.irisFailClosedSkips,
                this.solidPassMeshes,
                this.cutoutPassMeshes,
                this.translucentPassMeshes,
                this.fluidPassMeshes,
                this.boundaryQueueDepth,
                this.farQueueDepth,
                this.terrainCoverageVerdict,
                visualSourceVerdict,
                this.providerRenderAuthorityVerdict
        );
    }

    public VoxyTerrainProviderDiagnostics withOwnership(
            long exactOwnedCells,
            long lodOwnedCells,
            long parentFallbackCells,
            long invalidRejectedCells,
            long parentChildConflictCells,
            long boundaryExactSections,
            long boundaryParentFallbackSections,
            long boundaryRejectedSections,
            long boundaryMissingSections,
            long invalidRenderedSections,
            long boundarySourceRealChunkSections,
            long boundarySourceSurfacePreviewSections,
            long boundarySourceSyntheticPreviewSections,
            long boundarySourceUnknownSections,
            long boundarySourceUntrustedRealSections,
            long boundaryDegradedPreviewSections,
            long unsupportedPassSkips,
            long irisFailClosedSkips,
            String terrainCoverageVerdict,
            String visualSourceVerdict,
            String providerRenderAuthorityVerdict
    ) {
        return new VoxyTerrainProviderDiagnostics(
                this.policy,
                exactOwnedCells,
                lodOwnedCells,
                parentFallbackCells,
                invalidRejectedCells,
                parentChildConflictCells,
                boundaryExactSections,
                boundaryParentFallbackSections,
                boundaryRejectedSections,
                boundaryMissingSections,
                invalidRenderedSections,
                boundarySourceRealChunkSections,
                boundarySourceSurfacePreviewSections,
                boundarySourceSyntheticPreviewSections,
                boundarySourceUnknownSections,
                boundarySourceUntrustedRealSections,
                boundaryDegradedPreviewSections,
                unsupportedPassSkips,
                irisFailClosedSkips,
                this.solidPassMeshes,
                this.cutoutPassMeshes,
                this.translucentPassMeshes,
                this.fluidPassMeshes,
                this.boundaryQueueDepth,
                this.farQueueDepth,
                terrainCoverageVerdict,
                visualSourceVerdict,
                providerRenderAuthorityVerdict
        );
    }
}
