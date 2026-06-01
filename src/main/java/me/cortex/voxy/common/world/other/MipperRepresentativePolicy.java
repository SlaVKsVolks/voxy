package me.cortex.voxy.common.world.other;

import me.cortex.voxy.common.voxelization.VoxelizedSection;

public final class MipperRepresentativePolicy {
    private static final int FOLIAGE_CANOPY_MIN_SAMPLES = 3;

    private MipperRepresentativePolicy() {
    }

    public static boolean shouldUseSurfaceRepresentativePolicy(
            VoxelizedSection.SourceKind sourceKind,
            VoxelizedSection.Confidence confidence,
            VoxelizedSection.LightSourceKind lightSourceKind
    ) {
        VoxelizedSection.SourceKind safeSourceKind = sourceKind == null
                ? VoxelizedSection.SourceKind.UNKNOWN
                : sourceKind;
        VoxelizedSection.Confidence safeConfidence = confidence == null
                ? VoxelizedSection.Confidence.UNKNOWN
                : confidence;
        VoxelizedSection.LightSourceKind safeLightSourceKind = lightSourceKind == null
                ? VoxelizedSection.LightSourceKind.UNKNOWN
                : lightSourceKind;
        boolean previewSource = safeSourceKind == VoxelizedSection.SourceKind.SURFACE_PREVIEW
                || safeSourceKind == VoxelizedSection.SourceKind.SYNTHETIC_PREVIEW
                || safeLightSourceKind == VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW;
        boolean trustedRealFarTerrain = safeSourceKind == VoxelizedSection.SourceKind.REAL_CHUNK
                && safeConfidence.atLeast(VoxelizedSection.Confidence.HIGH)
                && VoxelizedSection.isTrustedLight(safeLightSourceKind);
        return previewSource || trustedRealFarTerrain;
    }

    public static boolean shouldPreferSkyExposedSurface(
            VoxelizedSection.SourceKind sourceKind,
            VoxelizedSection.Confidence confidence,
            VoxelizedSection.LightSourceKind lightSourceKind,
            boolean bestCanRepresentSurface,
            int bestSkyLight,
            int exposedSkyLight,
            boolean hasSkyExposedNonAir,
            int fluidSamples,
            int emissiveSamples
    ) {
        if (!shouldUseSurfaceRepresentativePolicy(sourceKind, confidence, lightSourceKind)
                || !bestCanRepresentSurface
                || !hasSkyExposedNonAir
                || fluidSamples > 0
                || emissiveSamples > 0) {
            return false;
        }
        if (lightSourceKind == VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW) {
            return true;
        }
        return bestSkyLight < 4 || exposedSkyLight > bestSkyLight + 2;
    }

    public static boolean shouldPreferFoliageCanopyOverSolid(
            boolean surfaceRepresentativePolicy,
            int foliageSamples,
            int solidSamples,
            int fluidSamples,
            int emissiveSamples,
            boolean bestIsSolid
    ) {
        return surfaceRepresentativePolicy
                && bestIsSolid
                && foliageSamples >= FOLIAGE_CANOPY_MIN_SAMPLES
                && solidSamples > 0
                && fluidSamples == 0
                && emissiveSamples == 0;
    }
}
