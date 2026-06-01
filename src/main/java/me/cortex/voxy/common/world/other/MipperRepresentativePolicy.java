package me.cortex.voxy.common.world.other;

public final class MipperRepresentativePolicy {
    private static final int FOLIAGE_CANOPY_MIN_SAMPLES = 3;

    private MipperRepresentativePolicy() {
    }

    public static boolean shouldPreferFoliageCanopyOverSolid(
            boolean surfacePreview,
            int foliageSamples,
            int solidSamples,
            int fluidSamples,
            int emissiveSamples,
            boolean bestIsSolid
    ) {
        return surfacePreview
                && bestIsSolid
                && foliageSamples >= FOLIAGE_CANOPY_MIN_SAMPLES
                && solidSamples > 0
                && fluidSamples == 0
                && emissiveSamples == 0;
    }
}
